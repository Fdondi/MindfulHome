package com.mindfulhome.ai.backend

import android.app.Activity
import android.content.Context
import com.mindfulhome.R
import com.mindfulhome.ai.GoogleSignInActivity
import kotlinx.coroutines.CancellationException

sealed class BackendSignInOutcome {
    data class Success(val email: String?) : BackendSignInOutcome()
    data object InteractiveStarted : BackendSignInOutcome()
    data object Cancelled : BackendSignInOutcome()
    data object NoAccount : BackendSignInOutcome()
    data object PlayServicesUnavailable : BackendSignInOutcome()
    data object ExchangeFailed : BackendSignInOutcome()
}

fun backendSignInOutcomeMessage(context: Context, outcome: BackendSignInOutcome): String? {
    val id = when (BackendSignInLogic.messageKind(outcome)) {
        BackendSignInLogic.MessageKind.None -> return null
        BackendSignInLogic.MessageKind.DidNotComplete -> R.string.google_sign_in_did_not_complete
        BackendSignInLogic.MessageKind.NoAccount -> R.string.google_sign_in_no_account
        BackendSignInLogic.MessageKind.PlayServices -> R.string.google_play_services_required
    }
    return context.getString(id)
}

/**
 * Silent Credential Manager first; if nothing is authorized yet, opens
 * [GoogleSignInActivity] so GIS is not hosted on the HOME task.
 */
suspend fun performBackendSignIn(context: Context): BackendSignInOutcome {
    return try {
        val silent = AuthManager.signInSilent(context)
        if (silent != null) return completeWithGoogleResult(context, silent)
        GoogleSignInActivity.start(context)
        BackendSignInOutcome.InteractiveStarted
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BackendSignInLogic.fromThrowable(e)
    }
}

/** Interactive Credential Manager on the dedicated host activity. */
suspend fun runInteractiveSignInOnHost(
    activity: Activity,
    forceAccountPicker: Boolean,
): BackendSignInOutcome {
    return try {
        val result = AuthManager.signInWithCredentialManager(activity, forceAccountPicker)
            ?: return BackendSignInOutcome.Cancelled
        completeWithGoogleResult(activity, result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BackendSignInLogic.fromThrowable(e)
    }
}

private suspend fun completeWithGoogleResult(
    context: Context,
    result: AuthManager.SignInResult,
): BackendSignInOutcome {
    result.email?.let { ApiKeyManager.saveSignedInEmail(context, it) }
    ApiKeyManager.saveGoogleIdToken(context, result.idToken)
    val exchanged = backendAuthHelper(context).completeBackendSignIn(result.idToken)
    return BackendSignInLogic.fromExchangeOk(exchanged, result.email)
}

private fun backendAuthHelper(context: Context): BackendAuthHelper = BackendAuthHelper(
    signInForExchange = { AuthManager.signInSilent(context)?.idToken },
    getSessionToken = { ApiKeyManager.getSessionToken(context) },
    saveSessionToken = { token, exp -> ApiKeyManager.saveSessionToken(context, token, exp) },
    clearSessionToken = { ApiKeyManager.clearSessionToken(context) },
    isSessionExpiringSoon = { ApiKeyManager.isSessionExpiringSoon(context) },
)
