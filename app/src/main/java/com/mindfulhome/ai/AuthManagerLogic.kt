package com.mindfulhome.ai

/**
 * Pure helpers for Google Credential Manager sign-in sequencing.
 *
 * [NoCredentialException] from One Tap (`GetGoogleIdOption`) does **not** mean
 * the device has no Google account. It often means the account has not been
 * used with this app yet. The Sign in with Google button flow
 * (`GetSignInWithGoogleOption`) lists device Google accounts instead of
 * launching system "add account".
 */
object AuthManagerLogic {

    enum class InteractiveGoogleSignInStep {
        /** One Tap / bottom sheet over any Google ID credential. */
        OneTapAnyAccount,

        /** Dedicated Sign in with Google button; shows the device account picker. */
        SignInWithGoogleButton,
    }

    fun initialInteractiveStep(forceAccountPicker: Boolean): InteractiveGoogleSignInStep =
        if (forceAccountPicker) {
            InteractiveGoogleSignInStep.SignInWithGoogleButton
        } else {
            InteractiveGoogleSignInStep.OneTapAnyAccount
        }

    fun fallbackInteractiveStep(
        failedStep: InteractiveGoogleSignInStep,
    ): InteractiveGoogleSignInStep? = when (failedStep) {
        InteractiveGoogleSignInStep.OneTapAnyAccount ->
            InteractiveGoogleSignInStep.SignInWithGoogleButton
        InteractiveGoogleSignInStep.SignInWithGoogleButton -> null
    }

    /**
     * Credential Manager reports a missing provider both when the Play Services
     * auth artifact is absent and when Play Services is missing or too old
     * (`isAvailableOnDevice` is false).
     */
    fun isProviderConfigurationFailure(throwable: Throwable): Boolean {
        val name = throwable.javaClass.name
        if (name.endsWith("GetCredentialProviderConfigurationException")) return true
        val message = throwable.message ?: return false
        return message.contains("no provider dependencies found")
    }

    /**
     * `CredentialProviderPlayServicesImpl.PRE_U_MIN_GMS_APK_VERSION` in
     * credentials-play-services-auth 1.6.0. Below this, the provider is treated
     * as absent and Credential Manager throws "no provider dependencies found".
     */
    const val CREDENTIAL_MANAGER_MIN_GMS_APK_VERSION = 230_815_045

    /**
     * Pre-Android 14 Credential Manager falls back to a GIS HiddenActivity.
     * That activity is destroyed when this app is the default HOME (`singleTask`
     * + `stateNotNeeded`), so silent getCredential from MainActivity must not run.
     */
    fun shouldSkipSilentCredentialManager(sdkInt: Int): Boolean = sdkInt < 34

    fun isGetCredentialCancellation(throwable: Throwable): Boolean {
        if (throwable.javaClass.name.endsWith("GetCredentialCancellationException")) return true
        val message = throwable.message ?: return false
        return message.contains("activity is cancelled by the user")
    }
}
